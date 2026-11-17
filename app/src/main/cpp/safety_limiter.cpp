#include "safety_limiter.h"

#include <algorithm>
#include <cmath>

namespace auralis {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kLookAheadSeconds = 0.0015;
constexpr double kReleaseSeconds = 0.120;
constexpr float kMinRequiredGain = 1.0e-5f;
constexpr std::size_t kResyncInterval = 1u << 16;

inline float sanitize(float v) noexcept { return std::isfinite(v) ? v : 0.0f; }

// Blackman-Harris window over |d| <= half (zero outside).
double window(double d, double half) {
    if (std::fabs(d) >= half) return 0.0;
    const double a = kPi * d / half;
    return 0.35875 + 0.48829 * std::cos(a) + 0.14128 * std::cos(2.0 * a) + 0.01168 * std::cos(3.0 * a);
}

}  // namespace

SafetyLimiter::SafetyLimiter(int sampleRate, double ceilingDb)
    : sampleRate_(std::max(8000, sampleRate)),
      ceiling_(std::pow(10.0, std::min(0.0, std::max(-12.0, ceilingDb)) / 20.0)),
      window_(std::max<std::size_t>(16, static_cast<std::size_t>(sampleRate_ * kLookAheadSeconds))),
      audioDelay_(window_ - 1 + static_cast<std::size_t>(kDetectDelay)),
      releaseCoef_(static_cast<float>(1.0 - std::exp(-1.0 / (sampleRate_ * kReleaseSeconds)))),
      audio_(audioDelay_ + 1),
      dqValue_(window_ + 1),
      dqIndex_(window_ + 1),
      avgRing_(window_, 1.0f) {
    // Polyphase windowed-sinc weights for the sub-sample positions p/4 between x[m] and x[m+1].
    // Taps cover x[m-5] .. x[m+6]; each phase is normalised to unity DC gain.
    for (int p = 1; p <= 3; ++p) {
        const double frac = p / 4.0;
        double sum = 0.0;
        double w[kTapsPerPhase];
        for (int k = 0; k < kTapsPerPhase; ++k) {
            const double d = static_cast<double>(k - 5) - frac;
            const double sinc = std::fabs(d) < 1e-12 ? 1.0 : std::sin(kPi * d) / (kPi * d);
            w[k] = sinc * window(d, 6.0);
            sum += w[k];
        }
        for (int k = 0; k < kTapsPerPhase; ++k) taps_[p - 1][k] = static_cast<float>(w[k] / sum);
    }
    reset();
}

void SafetyLimiter::setEnabled(bool enabled) noexcept {
    if (enabled_ == enabled) return;
    enabled_ = enabled;
    reset();
}

void SafetyLimiter::reset() noexcept {
    std::fill(audio_.begin(), audio_.end(), Stereo{});
    audioPos_ = 0;
    for (Stereo& s : hist_) s = Stereo{};
    dqHead_ = 0;
    dqCount_ = 0;
    detectIndex_ = 0;
    std::fill(avgRing_.begin(), avgRing_.end(), 1.0f);
    avgPos_ = 0;
    avgSum_ = static_cast<double>(window_);
    gain_ = 1.0f;
    minGain_ = 1.0f;
    prevGapPeak_ = 0.0f;
}

double SafetyLimiter::maxReductionDb() const noexcept {
    return 20.0 * std::log10(static_cast<double>(std::max(minGain_, kMinRequiredGain)));
}

void SafetyLimiter::step(const Stereo& in, Stereo& out) noexcept {
    // 1. Newest twelve frames; hist_[11] is x[n].
    for (int k = 0; k < kTapsPerPhase - 1; ++k) hist_[k] = hist_[k + 1];
    hist_[kTapsPerPhase - 1] = in;

    // 2. Oversampled peak in the gap between x[n-6] and x[n-5] (m = n-6): phases 1/4, 2/4, 3/4.
    float gapPeak = 0.0f;
    for (int p = 0; p < 3; ++p) {
        float accL = 0.0f, accR = 0.0f;
        for (int k = 0; k < kTapsPerPhase; ++k) {
            accL += taps_[p][k] * hist_[k].l;
            accR += taps_[p][k] * hist_[k].r;
        }
        gapPeak = std::max(gapPeak, std::max(std::fabs(accL), std::fabs(accR)));
    }

    // 3. Detector for frame j = n-6 (hist_[5]): its own samples plus the gap before it
    //    (computed one step ago) and the gap after it (just computed).
    const float samplePeak = std::max(std::fabs(hist_[5].l), std::fabs(hist_[5].r));
    const float peak = std::max(samplePeak, std::max(prevGapPeak_, gapPeak));
    prevGapPeak_ = gapPeak;

    float required = 1.0f;
    if (peak > static_cast<float>(ceiling_)) {
        required = std::max(kMinRequiredGain, static_cast<float>(ceiling_) / peak);
    }

    // 4. Sliding minimum over the last window_ detector values.
    const std::size_t cap = dqValue_.size();
    while (dqCount_ > 0) {
        const std::size_t back = (dqHead_ + dqCount_ - 1) % cap;
        if (dqValue_[back] >= required) --dqCount_; else break;
    }
    {
        const std::size_t slot = (dqHead_ + dqCount_) % cap;
        dqValue_[slot] = required;
        dqIndex_[slot] = detectIndex_;
        ++dqCount_;
    }
    while (dqCount_ > 0 && dqIndex_[dqHead_] + window_ <= detectIndex_) {
        dqHead_ = (dqHead_ + 1) % cap;
        --dqCount_;
    }
    const float windowMin = dqValue_[dqHead_];
    ++detectIndex_;

    // 5. Moving average of the sliding minimum over window_ values.
    avgSum_ += static_cast<double>(windowMin) - static_cast<double>(avgRing_[avgPos_]);
    avgRing_[avgPos_] = windowMin;
    avgPos_ = (avgPos_ + 1) % window_;
    if (detectIndex_ % kResyncInterval == 0) {
        double exact = 0.0;
        for (float v : avgRing_) exact += static_cast<double>(v);
        avgSum_ = exact;
    }
    const float smoothed = std::min(1.0f, static_cast<float>(avgSum_ / static_cast<double>(window_)));

    // 6. Attack follows the smoothed target immediately (it already ramps in over the look-ahead);
    //    release recovers slowly. The applied gain is never above the smoothed target.
    if (smoothed < gain_) gain_ = smoothed;
    else gain_ += (smoothed - gain_) * releaseCoef_;
    if (gain_ < minGain_) minGain_ = gain_;

    // 7. Store the new input and emit the frame that is audioDelay_ frames old.
    audio_[audioPos_] = in;
    audioPos_ = (audioPos_ + 1) % audio_.size();
    const Stereo delayed = audio_[audioPos_];  // oldest entry = x[n - audioDelay_]
    out.l = std::max(-1.0f, std::min(1.0f, delayed.l * gain_));
    out.r = std::max(-1.0f, std::min(1.0f, delayed.r * gain_));
}

void SafetyLimiter::process(float* x, std::size_t frames) noexcept {
    if (!enabled_ || x == nullptr) return;
    for (std::size_t i = 0; i < frames; ++i) {
        Stereo in{sanitize(x[2 * i]), sanitize(x[2 * i + 1])};
        Stereo out;
        step(in, out);
        x[2 * i] = out.l;
        x[2 * i + 1] = out.r;
    }
}

}  // namespace auralis
