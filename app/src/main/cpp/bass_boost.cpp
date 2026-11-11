#include "bass_boost.h"

#include <algorithm>
#include <cmath>

namespace auralis {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kButterworthQ = 0.70710678118654752;

// The boosted band: subsonic cut, then a low-pass that sets how high the boost reaches.
constexpr double kSubsonicHz = 30.0;
constexpr double kBassCeilingHz = 110.0;
// Corner and slope of the low shelf that carries the boost (half the boost is reached at the corner).
constexpr double kShelfHz = 110.0;
constexpr double kShelfQ = 0.75;
// Harmonics of the bass band are kept between these two corners.
constexpr double kHarmonicLowHz = 140.0;
constexpr double kHarmonicHighHz = 450.0;
// Small high-shelf lift that keeps the added weight from sounding dull.
constexpr double kAirHz = 8000.0;
constexpr double kAirMaxDb = 2.5;
// JBL-style "V" voicing: a dip in the boxy region and a lift in the vocal presence region.
constexpr double kTuckHz = 350.0;
constexpr double kTuckQ = 0.9;
constexpr double kTuckMaxDb = -2.0;
constexpr double kPresenceHz = 3000.0;
constexpr double kPresenceQ = 0.9;
constexpr double kPresenceMaxDb = 1.5;

// The boost eases off between these bass-band levels (dBFS) as the bass gets louder.
constexpr double kQuietDb = -24.0;
constexpr double kLoudDb = -4.0;
constexpr double kMinBoostFraction = 0.35;

constexpr double kHarmonicAmount = 0.5;
constexpr double kPeakKnee = 0.9;
constexpr double kSilent = 1.0e-4;
constexpr int kControlInterval = 16;

inline double smoothstep(double t) noexcept { return t * t * (3.0 - 2.0 * t); }

// Transparent below the knee, then bends smoothly towards (but never reaches) full scale.
inline double peakGuard(double v) noexcept {
    const double a = std::fabs(v);
    if (a <= kPeakKnee) return v;
    const double shaped = kPeakKnee + (1.0 - kPeakKnee) * std::tanh((a - kPeakKnee) / (1.0 - kPeakKnee));
    // Keep the float output strictly below full scale even after rounding.
    const double ceiling = static_cast<double>(std::nextafter(1.0f, 0.0f));
    const double safe = std::min(shaped, ceiling);
    return v < 0.0 ? -safe : safe;
}

inline double timeConstantCoeff(double seconds, double sampleRate) noexcept {
    return 1.0 - std::exp(-1.0 / (seconds * sampleRate));
}

} // namespace

void BassBoost::Biquad::lowPass(double fs, double hz, double q) noexcept {
    const double w0 = 2.0 * kPi * std::min(hz, fs * 0.45) / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    b0 = ((1.0 - cw) * 0.5) / a0;
    b1 = (1.0 - cw) / a0;
    b2 = b0;
    a1 = (-2.0 * cw) / a0;
    a2 = (1.0 - alpha) / a0;
}

void BassBoost::Biquad::highPass(double fs, double hz, double q) noexcept {
    const double w0 = 2.0 * kPi * std::min(hz, fs * 0.45) / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    b0 = ((1.0 + cw) * 0.5) / a0;
    b1 = (-(1.0 + cw)) / a0;
    b2 = b0;
    a1 = (-2.0 * cw) / a0;
    a2 = (1.0 - alpha) / a0;
}

void BassBoost::Biquad::highShelf(double fs, double hz, double q, double gainDb) noexcept {
    const double w0 = 2.0 * kPi * std::min(hz, fs * 0.45) / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double A = std::pow(10.0, gainDb / 40.0);
    const double beta = 2.0 * std::sqrt(A) * alpha;
    const double a0 = (A + 1.0) - (A - 1.0) * cw + beta;
    b0 = (A * ((A + 1.0) + (A - 1.0) * cw + beta)) / a0;
    b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cw)) / a0;
    b2 = (A * ((A + 1.0) + (A - 1.0) * cw - beta)) / a0;
    a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cw)) / a0;
    a2 = ((A + 1.0) - (A - 1.0) * cw - beta) / a0;
}

void BassBoost::Biquad::lowShelf(double fs, double hz, double q, double gainDb) noexcept {
    const double w0 = 2.0 * kPi * std::min(hz, fs * 0.45) / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double A = std::pow(10.0, gainDb / 40.0);
    const double beta = 2.0 * std::sqrt(A) * alpha;
    const double a0 = (A + 1.0) + (A - 1.0) * cw + beta;
    b0 = (A * ((A + 1.0) - (A - 1.0) * cw + beta)) / a0;
    b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cw)) / a0;
    b2 = (A * ((A + 1.0) - (A - 1.0) * cw - beta)) / a0;
    a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cw)) / a0;
    a2 = ((A + 1.0) + (A - 1.0) * cw - beta) / a0;
}

void BassBoost::Biquad::peaking(double fs, double hz, double q, double gainDb) noexcept {
    const double w0 = 2.0 * kPi * std::min(hz, fs * 0.45) / fs;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double A = std::pow(10.0, gainDb / 40.0);
    const double a0 = 1.0 + alpha / A;
    b0 = (1.0 + alpha * A) / a0;
    b1 = (-2.0 * cw) / a0;
    b2 = (1.0 - alpha * A) / a0;
    a1 = (-2.0 * cw) / a0;
    a2 = (1.0 - alpha / A) / a0;
}

void BassBoost::prepare(int sampleRate) noexcept {
    sampleRate_ = static_cast<double>(std::max(8000, sampleRate));
    smoothCoeff_ = timeConstantCoeff(0.030, sampleRate_);
    attackCoeff_ = timeConstantCoeff(0.004, sampleRate_);
    releaseCoeff_ = timeConstantCoeff(0.120, sampleRate_);
    gainCoeff_ = timeConstantCoeff(0.050, sampleRate_);
    subsonic_.highPass(sampleRate_, kSubsonicHz, kButterworthQ);
    liftSubsonic_.highPass(sampleRate_, kSubsonicHz, kButterworthQ);
    lowBand_.lowPass(sampleRate_, kBassCeilingHz, kButterworthQ);
    harmonicHigh_.highPass(sampleRate_, kHarmonicLowHz, kButterworthQ);
    harmonicLow_.lowPass(sampleRate_, kHarmonicHighHz, kButterworthQ);
    voicingAppliedAmount_ = -1.0;
    resetState();
}

void BassBoost::configure(bool enabled, double amount) noexcept {
    enabled_ = enabled;
    amountTarget_ = std::isfinite(amount) ? std::clamp(amount, 0.0, 1.0) : 0.0;
}

void BassBoost::resetState() noexcept {
    subsonic_.reset();
    liftSubsonic_.reset();
    shelf_.reset();
    shelf_.lowShelf(sampleRate_, kShelfHz, kShelfQ, 0.0);
    lowBand_.reset();
    harmonicHigh_.reset();
    harmonicLow_.reset();
    airLeft_.reset();
    airRight_.reset();
    tuckLeft_.reset();
    tuckRight_.reset();
    presenceLeft_.reset();
    presenceRight_.reset();
    amount_ = amountTarget_;
    mix_ = 0.0;
    env_ = 0.0;
    gain_ = 1.0;
    gainTarget_ = 1.0;
    boostDb_ = 0.0;
    controlCounter_ = 0;
}

void BassBoost::updateVoicing() noexcept {
    if (std::fabs(amount_ - voicingAppliedAmount_) < 0.01) return;
    voicingAppliedAmount_ = amount_;
    // Left and right share coefficients; only the filter memory differs.
    airLeft_.highShelf(sampleRate_, kAirHz, kButterworthQ, kAirMaxDb * amount_);
    airRight_.copyCoefficients(airLeft_);
    tuckLeft_.peaking(sampleRate_, kTuckHz, kTuckQ, kTuckMaxDb * amount_);
    tuckRight_.copyCoefficients(tuckLeft_);
    presenceLeft_.peaking(sampleRate_, kPresenceHz, kPresenceQ, kPresenceMaxDb * amount_);
    presenceRight_.copyCoefficients(presenceLeft_);
}

void BassBoost::updateControl() noexcept {
    const double envDb = 20.0 * std::log10(env_ + 1.0e-6);
    const double t = std::clamp((envDb - kQuietDb) / (kLoudDb - kQuietDb), 0.0, 1.0);
    const double fraction = 1.0 - (1.0 - kMinBoostFraction) * smoothstep(t);
    boostDb_ = kMaxBoostDb * amount_ * fraction;
    // The low band is added back on top of the signal, so the extra gain is (g - 1).
    gainTarget_ = std::pow(10.0, boostDb_ / 20.0);
    updateVoicing();
}

void BassBoost::process(float* x, std::size_t frames) noexcept {
    if (x == nullptr || frames == 0) return;
    if (!enabled_ && mix_ <= kSilent) {
        if (running_) {
            resetState();
            running_ = false;
        }
        return;
    }
    if (!running_) {
        // Starting from silence: begin with a settled control state at the requested strength.
        resetState();
        amount_ = amountTarget_;
        updateControl();
        gain_ = 1.0;
        running_ = true;
    }

    const double mixTarget = enabled_ ? 1.0 : 0.0;
    for (std::size_t i = 0; i < frames; ++i) {
        mix_ += (mixTarget - mix_) * smoothCoeff_;
        amount_ += (amountTarget_ - amount_) * smoothCoeff_;

        const double l = static_cast<double>(x[2 * i]);
        const double r = static_cast<double>(x[2 * i + 1]);
        const double mid = 0.5 * (l + r);

        // Bass band of the mid signal, with inaudible rumble removed.
        const double low = lowBand_.process(subsonic_.process(mid));

        // Follow the bass level: quick to rise, slow to fall.
        const double magnitude = std::fabs(low);
        env_ += (magnitude - env_) * (magnitude > env_ ? attackCoeff_ : releaseCoeff_);

        if (++controlCounter_ >= kControlInterval) {
            controlCounter_ = 0;
            updateControl();
            shelf_.lowShelf(sampleRate_, kShelfHz, kShelfQ, 20.0 * std::log10(gain_));
        }
        gain_ += (gainTarget_ - gain_) * gainCoeff_;

        // 1. Dynamic low boost: only the shelf's added energy, with inaudible rumble removed. Using a
        //    shelf (not a filtered copy of the bass band) keeps the boost in phase with the music, so
        //    it never cancels the low mids above the bass.
        const double lift = liftSubsonic_.process(shelf_.process(mid) - mid);

        // 2. Virtual bass: the bass band is normalised by its own level, shaped by a non-linear
        //    device with both even and odd harmonics, then scaled back to the bass level so the
        //    harmonics always follow the bass instead of changing with drive.
        const double norm = low / (env_ + 0.003);
        const double shaped = (std::tanh(2.5 * norm) + 0.3 * norm * norm) * env_;
        const double harmonics = harmonicLow_.process(harmonicHigh_.process(shaped));

        const double extra = lift + kHarmonicAmount * amount_ * harmonics;

        // 5. JBL voicing (mid dip, presence lift, air), blended in with the same smoothing as everything else.
        const double airL = airLeft_.process(presenceLeft_.process(tuckLeft_.process(l)));
        const double airR = airRight_.process(presenceRight_.process(tuckRight_.process(r)));
        const double baseL = l + (airL - l) * mix_;
        const double baseR = r + (airR - r) * mix_;

        // 6. Blend the effect first, then guard the final sample. Guarding before
        // the enable blend lets an unguarded part of a hot transient leak through.
        const double blendedL = baseL + extra * mix_;
        const double blendedR = baseR + extra * mix_;
        x[2 * i] = static_cast<float>(peakGuard(blendedL));
        x[2 * i + 1] = static_cast<float>(peakGuard(blendedR));
    }
}

} // namespace auralis
